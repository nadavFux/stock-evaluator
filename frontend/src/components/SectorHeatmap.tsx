import React, { useEffect, useState } from 'react';
import { Treemap, ResponsiveContainer, Tooltip } from 'recharts';
import { LayoutGrid, List as ListIcon } from 'lucide-react';

const SectorHeatmap: React.FC<{ sectors: number[], exchanges: string[] }> = ({ sectors, exchanges }) => {
    const [data, setData] = useState<any[]>([]);
    const [loading, setLoading] = useState(true);
    const [viewMode, setViewMode] = useState<'treemap' | 'list'>('treemap');

    useEffect(() => {
        const fetchSectorData = async () => {
            try {
                const sectorParams = sectors.join(',');
                const exchangeParams = exchanges.join(',');
                const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
                const res = await fetch(`${API_BASE_URL}/api/sectors/performance?sectors=${sectorParams}&exchanges=${exchangeParams}`);
                const sectorResults = await res.json();
                
                const formattedData = sectorResults.map((s: any) => ({
                    name: s.sectorName,
                    size: s.totalMarketCap,
                    performance: s.averageReturn, // Now a decimal proxy (-0.5 to 0.5)
                    count: s.stockCount
                })).sort((a: any, b: any) => b.size - a.size);
                
                setData(formattedData);
                setLoading(false);
            } catch (error) {
                console.error('Failed to fetch sector performance', error);
                setLoading(false);
            }
        };

        fetchSectorData();
    }, [sectors, exchanges]);

    const CustomContent = (props: any) => {
        const { x, y, width, height, name, performance } = props;
        // Map -0.1 to 0.1 to colors
        const color = performance > 0.05 ? '#059669' : performance > 0 ? '#10b981' : performance > -0.05 ? '#ef4444' : '#b91c1c';

        return (
            <g>
                <rect
                    x={x}
                    y={y}
                    width={width}
                    height={height}
                    style={{
                        fill: color,
                        stroke: '#0f172a',
                        strokeWidth: 2,
                    }}
                />
                {width > 60 && height > 40 && (
                    <foreignObject x={x} y={y} width={width} height={height}>
                        <div className="w-full h-full flex flex-col items-center justify-center p-2 text-center overflow-hidden">
                            <span className="text-white font-bold text-xs truncate w-full">{name}</span>
                            <span className="text-white/80 text-[10px]">{(performance * 100).toFixed(1)}%</span>
                        </div>
                    </foreignObject>
                )}
            </g>
        );
    };

    if (loading) return <div className="h-full flex items-center justify-center text-slate-500">Loading Heatmap...</div>;

    return (
        <div className="w-full h-full flex flex-col gap-4">
            <div className="flex justify-end gap-2 mb-2">
                <button 
                    onClick={() => setViewMode('treemap')}
                    className={`p-1.5 rounded-lg border ${viewMode === 'treemap' ? 'bg-blue-600 border-blue-500 text-white' : 'bg-slate-800 border-slate-700 text-slate-400'}`}
                >
                    <LayoutGrid size={18} />
                </button>
                <button 
                    onClick={() => setViewMode('list')}
                    className={`p-1.5 rounded-lg border ${viewMode === 'list' ? 'bg-blue-600 border-blue-500 text-white' : 'bg-slate-800 border-slate-700 text-slate-400'}`}
                >
                    <ListIcon size={18} />
                </button>
            </div>

            <div className="flex-1 min-h-0">
                {viewMode === 'treemap' ? (
                    <ResponsiveContainer width="100%" height="100%">
                        <Treemap
                            data={data}
                            dataKey="size"
                            aspectRatio={4 / 3}
                            stroke="#fff"
                            content={<CustomContent />}
                        >
                            <Tooltip 
                                content={({ active, payload }) => {
                                    if (active && payload && payload.length) {
                                        const d = payload[0].payload;
                                        return (
                                            <div className="bg-slate-900 p-3 rounded-lg border border-slate-700 shadow-xl">
                                                <p className="text-white font-bold mb-1">{d.name}</p>
                                                <p className="text-slate-400 text-xs">Score Proxy: <span className={d.performance > 0 ? 'text-emerald-400' : 'text-red-400'}>{(d.performance * 100).toFixed(2)}%</span></p>
                                                <p className="text-slate-400 text-xs">Total Cap: ${(d.size / 1e9).toFixed(2)}B</p>
                                                <p className="text-slate-400 text-xs">Stocks: {d.count}</p>
                                            </div>
                                        );
                                    }
                                    return null;
                                }}
                            />
                        </Treemap>
                    </ResponsiveContainer>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 overflow-y-auto max-h-full pr-2 scrollbar-thin">
                        {data.map((d, i) => (
                            <div key={i} className="bg-slate-900/50 p-4 rounded-xl border border-slate-800 flex items-center justify-between group hover:border-slate-600 transition-all">
                                <div className="flex flex-col">
                                    <span className="text-white font-bold">{d.name}</span>
                                    <span className="text-slate-500 text-xs">{d.count} stocks • ${(d.size / 1e9).toFixed(1)}B</span>
                                </div>
                                <div className={`px-3 py-1 rounded-lg text-sm font-bold ${d.performance > 0 ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'}`}>
                                    {d.performance > 0 ? '+' : ''}{(d.performance * 100).toFixed(1)}%
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
};

export default SectorHeatmap;
